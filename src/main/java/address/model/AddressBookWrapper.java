package address.model;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Helper class to wrap a list of persons. This is used for saving the
 * list of persons to XML.
 * 
 * @author Marco Jakob
 */
@XmlRootElement(name = "addressbook")
public class AddressBookWrapper {

    private List<Person> persons = new ArrayList<>(); // so empty lists from file will not be null
    private List<ContactGroup> groups = new ArrayList<>(); // ditto

    @XmlElement(name = "persons")
    public List<Person> getPersons() {
        return persons;
    }

    @XmlElement(name = "groups")
    public List<ContactGroup> getGroups() {
        return groups;
    }

    public void setPersons(List<Person> persons) {
        this.persons = persons;
    }

    public void setGroups(List<ContactGroup> groups) {
        this.groups = groups;
    }
}